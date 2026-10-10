package com.lezi.babylog.feature.settings

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Brightness6
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.ChildCare
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.lezi.babylog.core.common.productUiError
import com.lezi.babylog.core.common.LocalOpFailureCopy
import com.lezi.babylog.core.datastore.SettingsStore
import com.lezi.babylog.core.model.RecordItemIdentity
import com.lezi.babylog.core.model.RecordType
import com.lezi.babylog.core.model.SettingsLocal
import com.lezi.babylog.core.model.elderModeAfterSwitch
import com.lezi.babylog.core.model.elderModeEnabled
import com.lezi.babylog.core.model.deviceLayoutSnapshot
import com.lezi.babylog.core.model.birthWeightValidationError
import com.lezi.babylog.core.ui.AppUpdateOutcomeDialogs
import com.lezi.babylog.core.ui.BabyBirthdayDatePickerDialog
import com.lezi.babylog.core.ui.BabyProfileFormFields
import com.lezi.babylog.core.ui.RecordTypeIcon
import com.lezi.babylog.designsystem.LeziAlertDialog
import com.lezi.babylog.designsystem.LeziFilterChip
import com.lezi.babylog.designsystem.LeziIconSize
import com.lezi.babylog.designsystem.LeziMenuIcon
import com.lezi.babylog.designsystem.LeziSecondaryButton
import com.lezi.babylog.designsystem.LeziShapes
import com.lezi.babylog.designsystem.LeziSpacing
import com.lezi.babylog.designsystem.LeziSurfacePanel
import com.lezi.babylog.designsystem.LeziSwitch
import com.lezi.babylog.designsystem.LeziTextButton
import com.lezi.babylog.designsystem.LeziTextButtonTone
import com.lezi.babylog.designsystem.LeziTypography
import com.lezi.babylog.designsystem.PageScaffoldBackground
import com.lezi.babylog.designsystem.SectionHeading
import com.lezi.babylog.designsystem.dismissKeyboardOnTap
import com.lezi.babylog.domain.CareLog
import com.lezi.babylog.domain.CustomRecordItem
import com.lezi.babylog.domain.localdata.LocalRecordsClearCommittedException
import com.lezi.babylog.domain.localdata.LocalDataClearCoordinator
import com.lezi.babylog.domain.localdata.LocalDataClearScope
import com.lezi.babylog.domain.calendar.SystemCalendarConfigurationCoordinator
import com.lezi.babylog.domain.calendar.SystemCalendarPort
import com.lezi.babylog.feature.settings.calendar.*
import com.lezi.babylog.feature.settings.command.ClearRecordsPrimaryAction
import com.lezi.babylog.feature.settings.command.CustomItemSaveKind
import com.lezi.babylog.feature.settings.command.SettingsClearRecordsController
import com.lezi.babylog.feature.settings.command.SettingsClearRecordsStep
import com.lezi.babylog.feature.settings.command.clearRecordsPrimaryAction
import com.lezi.babylog.feature.settings.command.SettingsCustomItemCommandGate
import com.lezi.babylog.feature.settings.command.projectSettingsInstallOutcome
import com.lezi.babylog.feature.settings.record.*
import com.lezi.babylog.sync.AppUpdateMetadata
import com.lezi.babylog.sync.appupdate.AppUpdateUiOutcome
import com.lezi.babylog.sync.ClientAppVersion
import com.lezi.babylog.sync.SyncPort
import com.lezi.babylog.sync.appupdate.appUpdateInstallUiOutcome
import com.lezi.babylog.sync.appupdate.appUpdateUiOutcome
import com.lezi.babylog.sync.appupdate.localAppVersionLabel
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

internal const val SETTINGS_MENU_ROW_MORE_TAG = "settings_menu_row_more"

internal fun clearRecordsFailureCopy(error: Throwable): String = when {
    error is LocalRecordsClearCommittedException && error.familyServerRetained ->
        "本机记录可能已部分清理，家庭服务器上的记录仍保留，请重试"
    error is LocalRecordsClearCommittedException -> "本机记录可能已部分清理，请重试"
    else -> productUiError(error, "清除没有完成，记录保持原样，可重试")
}

data class SettingsUi(
    val settings: SettingsLocal = SettingsLocal(),
    val showAvgSleep: Boolean = false,
    val comparePrevWeek: Boolean = false,
    val customItems: List<CustomRecordItem> = emptyList(),
    val isFamilyJoined: Boolean = false,
    val familyRole: com.lezi.babylog.sync.session.FamilyRole = com.lezi.babylog.sync.session.FamilyRole.None,
    val systemCalendarTargetSummary: String = "未配置",
    val appVersionName: String = ClientAppVersion.FALLBACK.versionName,
) {
    val appVersionLabel: String
        get() = localAppVersionLabel(appVersionName)
}

private data class LocalSettingsUi(
    val settings: SettingsLocal,
    val showAvgSleep: Boolean,
    val comparePrevWeek: Boolean,
    val systemCalendarTargetSummary: String,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsStore: SettingsStore,
    private val careLog: CareLog,
    private val syncPort: SyncPort,
    private val systemCalendarPort: SystemCalendarPort,
    private val systemCalendarConfiguration: SystemCalendarConfigurationCoordinator,
    private val localDataClearCoordinator: LocalDataClearCoordinator,
    private val clientAppVersion: ClientAppVersion,
) : ViewModel() {
    private val customItemCommands = SettingsCustomItemCommandGate()
    private val clearRecordsController = SettingsClearRecordsController(
        clearRecords = {
            localDataClearCoordinator.clear(LocalDataClearScope.RecordsOnly)
        },
        failureCopy = ::clearRecordsFailureCopy,
    )
    private val settingsWithCalendarTarget = settingsStore.settings.map { settings ->
        val hasPermission = systemCalendarPort.hasCalendarPermission()
        val targets = if (hasPermission) {
            systemCalendarPort.listWritableCalendars()
        } else {
            emptyList()
        }
        settings to systemCalendarTargetSummary(
            calendarId = settings.systemCalendarId,
            hasPermission = hasPermission,
            targets = targets,
        )
    }

    private val localSettings = combine(
        settingsWithCalendarTarget,
        settingsStore.showAvgSleep,
        settingsStore.comparePrevWeek,
    ) { (settings, targetSummary), showAvgSleep, comparePrevWeek ->
        LocalSettingsUi(settings, showAvgSleep, comparePrevWeek, targetSummary)
    }

    val ui = combine(
        localSettings,
        careLog.observeCustomItems(),
        syncPort.sessionPresentation(),
    ) { local, customItems, session ->
        SettingsUi(
            settings = local.settings,
            showAvgSleep = local.showAvgSleep,
            comparePrevWeek = local.comparePrevWeek,
            customItems = customItems,
            isFamilyJoined = session.isJoined,
            familyRole = session.role,
            systemCalendarTargetSummary = local.systemCalendarTargetSummary,
            appVersionName = clientAppVersion.versionName,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        SettingsUi(appVersionName = clientAppVersion.versionName),
    )

    private val _appUpdateOutcome = MutableStateFlow<AppUpdateUiOutcome?>(null)
    val appUpdateOutcome: StateFlow<AppUpdateUiOutcome?> = _appUpdateOutcome.asStateFlow()
    private val _checkingAppUpdate = MutableStateFlow(false)
    val checkingAppUpdate: StateFlow<Boolean> = _checkingAppUpdate.asStateFlow()
    private val _installingAppUpdate = MutableStateFlow(false)
    val installingAppUpdate: StateFlow<Boolean> = _installingAppUpdate.asStateFlow()
    private val _appUpdateInstallFeedback = MutableStateFlow<String?>(null)
    val appUpdateInstallFeedback: StateFlow<String?> = _appUpdateInstallFeedback.asStateFlow()
    private val _forcedInstallPermissionRequired = MutableStateFlow(false)
    val forcedInstallPermissionRequired: StateFlow<Boolean> =
        _forcedInstallPermissionRequired.asStateFlow()
    internal val customItemCommandState = customItemCommands.state
    internal val clearRecordsState = clearRecordsController.state

    fun checkAppUpdate() {
        if (_checkingAppUpdate.value || _installingAppUpdate.value) return
        viewModelScope.launch {
            _checkingAppUpdate.value = true
            _appUpdateInstallFeedback.value = null
            _forcedInstallPermissionRequired.value = false
            try {
                val result = syncPort.checkAppUpdate()
                // Pass live force shell so secondary dialog never claims Optional/UpToDate
                // while root ForcedAppUpdateState is retained (AUDIT-20260801-P1-01).
                val activeForce = syncPort.availableForcedAppUpdate().first()
                _appUpdateOutcome.value = appUpdateUiOutcome(
                    result = result,
                    failureCopy = { error ->
                        productUiError(error, LocalOpFailureCopy.APP_UPDATE_CHECK)
                    },
                    activeForcedAppUpdate = activeForce,
                )
            } finally {
                _checkingAppUpdate.value = false
            }
        }
    }

    fun dismissAppUpdateOutcome() {
        val current = _appUpdateOutcome.value
        // Forced updates cannot be dismissed ("稍后" is not allowed). Root force shell
        // remains authoritative; this dialog must not bypass PackageUnknown either.
        if (current is AppUpdateUiOutcome.ForcedUpdate) return
        if (current is AppUpdateUiOutcome.ForcedUpdatePackageUnknown) return
        if (current is AppUpdateUiOutcome.OptionalUpdate) {
            // "稍后" / dismiss: process-session suppress for handshake banner.
            syncPort.dismissOptionalAppUpdate(current.metadata.versionCode)
        }
        _appUpdateOutcome.value = null
        _appUpdateInstallFeedback.value = null
        _forcedInstallPermissionRequired.value = false
    }

    /**
     * Download → sha256 → staged archive identity (packageName/versionCode/signing)
     * → PackageInstaller for optional or forced update (see [SyncPort.installAvailableAppUpdate]).
     */
    fun installOptionalUpdate(metadata: AppUpdateMetadata) {
        if (_installingAppUpdate.value) return
        viewModelScope.launch {
            _installingAppUpdate.value = true
            val activeOutcome = _appUpdateOutcome.value
                ?: AppUpdateUiOutcome.OptionalUpdate(metadata)
            _appUpdateInstallFeedback.value = null
            _forcedInstallPermissionRequired.value = false
            if (activeOutcome is AppUpdateUiOutcome.ForcedUpdate) {
                _appUpdateInstallFeedback.value = "正在从家庭服务器下载更新包…"
            } else {
                _appUpdateOutcome.value = AppUpdateUiOutcome.Message(
                    title = "正在下载",
                    body = "正在从家庭服务器下载更新包…",
                )
            }
            try {
                val result = syncPort.installAvailableAppUpdate(metadata)
                val installOutcome = appUpdateInstallUiOutcome(result) { error ->
                    productUiError(error, LocalOpFailureCopy.APP_UPDATE_INSTALL)
                }
                val projection = projectSettingsInstallOutcome(activeOutcome, installOutcome)
                _appUpdateOutcome.value = projection.outcome
                _appUpdateInstallFeedback.value = projection.feedback
                _forcedInstallPermissionRequired.value = projection.requiresInstallPermission
            } finally {
                _installingAppUpdate.value = false
            }
        }
    }

    fun markForcedInstallPermissionOpened() {
        _forcedInstallPermissionRequired.value = false
    }

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
    fun setVisualStyle(key: String) = viewModelScope.launch { settingsStore.setVisualStyle(key) }
    fun setElderMode(mode: String) = viewModelScope.launch { settingsStore.setElderMode(mode) }
    fun setPreferredHand(hand: String) = viewModelScope.launch { settingsStore.setPreferredHand(hand) }
    fun setTimelineOrder(order: String) =
        viewModelScope.launch { settingsStore.setTimelineOrder(order) }
    fun setCarePlanLocalReminders(enabled: Boolean) =
        viewModelScope.launch { careLog.setCarePlanLocalRemindersEnabled(enabled) }

    private val calendarSetupCommand = com.lezi.babylog.feature.settings.calendar.SystemCalendarSetupCommand(systemCalendarConfiguration)
    val calendarSetupState = calendarSetupCommand.state

    fun consumeCalendarSetupResult() = calendarSetupCommand.consume()

    fun confirmSystemCalendar(calendarId: String, disclosureLevel: Int) = viewModelScope.launch {
        calendarSetupCommand.confirm(com.lezi.babylog.feature.settings.calendar.SystemCalendarSetupSelection(calendarId, disclosureLevel))
    }

    fun disableSystemCalendar() = viewModelScope.launch { calendarSetupCommand.disable() }

    fun setShowAvgSleep(enabled: Boolean) =
        viewModelScope.launch { settingsStore.setShowAvgSleep(enabled) }
    fun setComparePrevWeek(enabled: Boolean) =
        viewModelScope.launch { settingsStore.setComparePrevWeek(enabled) }
    fun toggleHiddenItem(typeKey: String) = viewModelScope.launch {
        customItemCommands.runLayout {
            // Read inside the serialized command, not from a potentially stale UI projection.
            val current = settingsStore.settings.first().deviceLayoutSnapshot()
            settingsStore.setDeviceLayoutSnapshot(
                current.copy(
                    hiddenItems = if (typeKey in current.hiddenItems) {
                        current.hiddenItems - typeKey
                    } else {
                        current.hiddenItems + typeKey
                    },
                ),
            )
        }
    }

    fun addCustomItem(name: String, iconSlot: Int, onDone: (String?) -> Unit) {
        viewModelScope.launch {
            var failure: String? = null
            val accepted = customItemCommands.runSave(CustomItemSaveKind.Add) {
                failure = try {
                    careLog.addCustomItem(name, iconSlot)
                    null
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    productUiError(error, "添加失败")
                }
            }
            onDone(if (accepted) failure else "正在保存，请稍候")
        }
    }

    fun updateCustomItem(item: CustomRecordItem, onDone: (String?) -> Unit) {
        viewModelScope.launch {
            var failure: String? = null
            val accepted = customItemCommands.runSave(CustomItemSaveKind.Update) {
                failure = try {
                    careLog.updateCustomItem(item)
                    null
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    productUiError(error, "保存失败")
                }
            }
            onDone(if (accepted) failure else "正在保存，请稍候")
        }
    }

    fun moveCustomItem(id: Long, delta: Int) =
        viewModelScope.launch {
            customItemCommands.runLayout {
                try {
                    careLog.moveCustomItem(id, delta)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    // Existing row order remains authoritative; the next projection restores it.
                }
            }
        }

    fun deleteCustomItem(id: Long, onDone: (String?) -> Unit = {}) {
        viewModelScope.launch {
            onDone(executeCustomItemDelete(id, careLog::deleteCustomItem))
        }
    }

    /** Domain ACL: owner/admin manage all; members only their own definitions. */
    suspend fun canManageCustomItem(item: CustomRecordItem): Boolean =
        careLog.canManageCustomItem(item)

    /** Clears records only — babies are never deleted from settings. */
    fun requestClearRecords() = clearRecordsController.request()

    fun continueClearRecords() = clearRecordsController.continueToFinal()

    fun dismissClearRecords() = clearRecordsController.dismiss()

    fun confirmClearRecords() {
        viewModelScope.launch { clearRecordsController.confirm() }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsRoute(
    onOpenExport: () -> Unit = {},
    onOpenSearch: () -> Unit = {},
    onOpenCalendar: () -> Unit = {},
    vm: SettingsViewModel = hiltViewModel(),
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val appUpdateOutcome by vm.appUpdateOutcome.collectAsStateWithLifecycle()
    val checkingAppUpdate by vm.checkingAppUpdate.collectAsStateWithLifecycle()
    val installingAppUpdate by vm.installingAppUpdate.collectAsStateWithLifecycle()
    val appUpdateInstallFeedback by vm.appUpdateInstallFeedback.collectAsStateWithLifecycle()
    val forcedInstallPermissionRequired by
        vm.forcedInstallPermissionRequired.collectAsStateWithLifecycle()
    val customItemCommandState by vm.customItemCommandState.collectAsStateWithLifecycle()
    val clearRecordsState by vm.clearRecordsState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val clearRecordsCopy = clearRecordsConfirmationCopy(ui.isFamilyJoined)
    var showDisplay by rememberSaveable { mutableStateOf(false) }
    var showRecordSettings by rememberSaveable { mutableStateOf(false) }
    var showCustomItems by rememberSaveable { mutableStateOf(false) }
    var showSystemCalendarSetup by rememberSaveable { mutableStateOf(false) }
    var notificationPermissionGranted by remember {
        mutableStateOf(
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) == PackageManager.PERMISSION_GRANTED,
        )
    }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> notificationPermissionGranted = granted }
    LaunchedEffect(showRecordSettings) {
        if (showRecordSettings) {
            notificationPermissionGranted =
                Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) == PackageManager.PERMISSION_GRANTED
        }
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

            SectionHeading(title = "查找与管理")
            SettingsMenuRow(
                "搜索全部记录",
                "按类型、详情或备注查找",
                icon = Icons.Outlined.Search,
                actionLabel = "搜索全部记录",
                onClick = onOpenSearch,
            )
            SettingsMenuRow(
                "导出数据",
                "PDF / TXT 导出与分享",
                icon = Icons.Outlined.Upload,
                actionLabel = "打开数据导出",
                onClick = onOpenExport,
            )
            SettingsMenuRow(
                "日程",
                "本机提醒与日程列表",
                icon = Icons.Outlined.CalendarMonth,
                actionLabel = "打开日程",
                onClick = onOpenCalendar,
            )

            SectionHeading(title = "外观与偏好")
            // 只读展示当前模式；三态切换集中在「显示设置」对话框一处。
            SettingsMenuRow(
                title = "深色模式",
                subtitle = "当前：${when (ui.settings.darkMode) {
                    "dark" -> "深色"
                    "light" -> "浅色"
                    else -> "跟随系统"
                }}",
                icon = Icons.Outlined.DarkMode,
                actionLabel = "打开显示设置调整深色模式",
                onClick = { showDisplay = true },
            )
            SettingsMenuRow(
                "记录设置",
                "分项目参数、护理计划与系统日历",
                icon = Icons.Outlined.Menu,
                actionLabel = "打开记录设置",
                onClick = { showRecordSettings = true },
            )
            SettingsMenuRow(
                "自定义项目",
                "创建、重命名、图标与本机显示",
                icon = Icons.Outlined.Add,
                actionLabel = "管理自定义项目",
                onClick = { showCustomItems = true },
            )
            SettingsMenuRow(
                "显示设置",
                "界面模板与主题",
                icon = Icons.Outlined.Brightness6,
                actionLabel = "打开显示设置",
                onClick = { showDisplay = true },
            )

            SectionHeading(title = "数据")
            SettingsMenuRow(
                title = "清除全部记录",
                subtitle = "不删除宝宝档案",
                icon = Icons.Outlined.DeleteForever,
                actionLabel = "清除全部记录",
                onClick = vm::requestClearRecords,
                danger = true,
            )

            SectionHeading(title = "关于")
            LeziSurfacePanel(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(
                        enabled = !checkingAppUpdate,
                        onClickLabel = "检查更新",
                        role = Role.Button,
                        onClick = vm::checkAppUpdate,
                    ),
                bottomBand = true,
            ) {
                Text("乐记", style = LeziTypography.TitleSm)
                Text(
                    if (checkingAppUpdate) "正在检查更新…" else ui.appVersionLabel,
                    style = LeziTypography.Meta,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(LeziSpacing.Xxl))
        }
    }

    AppUpdateOutcomeDialogs(
        outcome = appUpdateOutcome,
        checkingAppUpdate = checkingAppUpdate,
        installingAppUpdate = installingAppUpdate,
        installFeedback = appUpdateInstallFeedback,
        forcedInstallPermissionRequired = forcedInstallPermissionRequired,
        onDismissOutcome = vm::dismissAppUpdateOutcome,
        onInstallUpdate = vm::installOptionalUpdate,
        onRetryCheck = vm::checkAppUpdate,
        onForcedInstallPermissionOpened = vm::markForcedInstallPermissionOpened,
    )

    if (showRecordSettings) {
        RecordSettingsDialog(
            settings = ui.settings,
            carePlanRemindersEnabled = ui.settings.carePlanLocalRemindersEnabled,
            onCarePlanRemindersEnabled = { enabled ->
                vm.setCarePlanLocalReminders(enabled)
                if (
                    enabled &&
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    !notificationPermissionGranted
                ) {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            },
            notificationPermissionWarning = carePlanNotificationPermissionWarning(
                remindersEnabled = ui.settings.carePlanLocalRemindersEnabled,
                sdkInt = Build.VERSION.SDK_INT,
                permissionGranted = notificationPermissionGranted,
            ),
            onOpenNotificationSettings = {
                val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                runCatching { context.startActivity(intent) }
            },
            systemCalendarEnabled = ui.settings.systemCalendarEnabled &&
                !ui.settings.systemCalendarId.isNullOrBlank(),
            systemCalendarSummary = ui.systemCalendarTargetSummary,
            systemCalendarDisclosureSummary = systemCalendarDisclosureLabel(
                ui.settings.systemCalendarDisclosureLevel,
            ),
            onConfigureSystemCalendar = {
                showSystemCalendarSetup = true
            },
            onTimerEnabled = vm::setTimer,
            onRecordAt = vm::setRecordAt,
            onInterval = vm::setInterval,
            onAmountStep = vm::setStep,
            onFeverAdvice = vm::setInfantFeverAdvice,
            onDismiss = { showRecordSettings = false },
        )
    }

    if (showSystemCalendarSetup) {
        val calendarCommandState by vm.calendarSetupState.collectAsStateWithLifecycle()
        SystemCalendarSetupDialog(
            currentCalendarId = ui.settings.systemCalendarId,
            currentDisclosureLevel = ui.settings.systemCalendarDisclosureLevel,
            commandState = calendarCommandState,
            onConfirm = { selection ->
                vm.confirmSystemCalendar(selection.calendarId, selection.disclosureLevel)
            },
            onDisable = { vm.disableSystemCalendar() },
            onDismiss = {
                if (!calendarCommandState.busy) {
                    vm.consumeCalendarSetupResult()
                    showSystemCalendarSetup = false
                }
            },
        )
    }

    if (showDisplay) {
        LeziAlertDialog(
            onDismissRequest = { showDisplay = false },
            title = { Text("显示设置") },
            text = {
                ScrollableDialogColumn {
                    Text("界面模板", style = LeziTypography.Label)
                    VisualStylePicker(
                        selected = ui.settings.visualStyle,
                        onSelect = vm::setVisualStyle,
                    )
                    Text(
                        "两套模板共用同一套记录图标，只改变密度、圆角与配色。",
                        style = LeziTypography.Meta,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("长辈模式", style = LeziTypography.Label)
                            Text(
                                "大字、无衬线、大按钮、强对比",
                                style = LeziTypography.Meta,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        LeziSwitch(
                            checked = elderModeEnabled(ui.settings.elderMode),
                            onCheckedChange = { enabled ->
                                vm.setElderMode(
                                    elderModeAfterSwitch(ui.settings.elderMode, enabled),
                                )
                            },
                        )
                    }
                    if (elderModeEnabled(ui.settings.elderMode)) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            listOf(
                                "l1" to "大",
                                "l2" to "特大",
                                "l3" to "超大",
                            ).forEach { (key, label) ->
                                LeziFilterChip(
                                    selected = ui.settings.elderMode == key,
                                    onClick = { vm.setElderMode(key) },
                                    label = label,
                                )
                            }
                        }
                    }
                    Text("单手操作 · 惯用手", style = LeziTypography.Label)
                    Text(
                        "影响圆盘调时与表单靠边；首页常用坞按你编排的左右序，不镜像。",
                        style = LeziTypography.Meta,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        listOf("left" to "左手", "right" to "右手").forEach { (key, label) ->
                            LeziFilterChip(selected = ui.settings.preferredHand == key, onClick = { vm.setPreferredHand(key) }, label = label)
                        }
                    }
                    Text("深色模式", style = LeziTypography.Label)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        listOf("system" to "跟随系统", "light" to "浅色", "dark" to "深色").forEach { (k, label) ->
                            LeziFilterChip(selected = ui.settings.darkMode == k, onClick = { vm.setDark(k) }, label = label)
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
                            LeziFilterChip(selected = ui.settings.timePickerStyle == key, onClick = { vm.setTimePickerStyle(key) }, label = label)
                        }
                    }
                    Text("时间选择分钟步进", style = LeziTypography.Label)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        listOf(1 to "1 分钟", 5 to "5 分钟").forEach { (step, label) ->
                            LeziFilterChip(selected = ui.settings.timeStepMin == step, onClick = { vm.setTimeStep(step) }, label = label)
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
                            LeziFilterChip(selected = ui.settings.timelineOrder == key, onClick = { vm.setTimelineOrder(key) }, label = label)
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("显示日均睡眠")
                        LeziSwitch(
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
                        LeziSwitch(
                            checked = ui.comparePrevWeek,
                            onCheckedChange = vm::setComparePrevWeek,
                        )
                    }
                }
            },
            confirmButton = { LeziTextButton(label = "完成", onClick = { showDisplay = false }) },
        )
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
            saveBusyLabel = when (customItemCommandState.saveKind) {
                CustomItemSaveKind.Add -> "添加中…"
                CustomItemSaveKind.Update -> "保存中…"
                null -> null
            },
            layoutBusy = customItemCommandState.layoutBusy,
            onDismiss = { showCustomItems = false },
            onAdd = vm::addCustomItem,
            onUpdate = vm::updateCustomItem,
            onMove = vm::moveCustomItem,
            onDelete = vm::deleteCustomItem,
            onToggleLocalHidden = { id ->
                ui.customItems.firstOrNull { it.id == id }?.let { item ->
                    vm.toggleHiddenItem(
                        RecordItemIdentity.custom(item.id, item.clientUuid).catalogKey,
                    )
                }
            },
            canManage = { item -> item.id in manageableIds },
        )
    }

    // Layout (常用/所有记录) is edited on the record page; no parallel settings dialogs.

    if (clearRecordsState.step == SettingsClearRecordsStep.FirstConfirm) {
        LeziAlertDialog(
            onDismissRequest = vm::dismissClearRecords,
            title = { Text("确认清除记录？") },
            text = {
                Text(clearRecordsCopy.firstPrompt)
            },
            confirmButton = {
                LeziTextButton(
                    label = "继续",
                    onClick = {
                        when (clearRecordsPrimaryAction(clearRecordsState.step)) {
                            ClearRecordsPrimaryAction.Continue -> vm.continueClearRecords()
                            ClearRecordsPrimaryAction.Confirm -> vm.confirmClearRecords()
                            null -> Unit
                        }
                    },
                    tone = LeziTextButtonTone.Destructive,
                )
            },
            dismissButton = {
                LeziTextButton(label = "取消", onClick = vm::dismissClearRecords)
            },
        )
    }
    if (clearRecordsState.step == SettingsClearRecordsStep.FinalConfirm) {
        LeziAlertDialog(
            onDismissRequest = vm::dismissClearRecords,
            title = { Text("最后确认") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(LeziSpacing.Sm)) {
                    Text(
                        clearRecordsCopy.finalPrompt,
                    )
                    clearRecordsState.error?.let {
                        Text(it, color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                LeziTextButton(
                    label = if (clearRecordsState.clearing) "清除中…" else "清除记录",
                    onClick = {
                        when (clearRecordsPrimaryAction(clearRecordsState.step)) {
                            ClearRecordsPrimaryAction.Continue -> vm.continueClearRecords()
                            ClearRecordsPrimaryAction.Confirm -> vm.confirmClearRecords()
                            null -> Unit
                        }
                    },
                    enabled = !clearRecordsState.clearing,
                    tone = LeziTextButtonTone.Destructive,
                )
            },
            dismissButton = {
                LeziTextButton(label = "取消", onClick = vm::dismissClearRecords, enabled = !clearRecordsState.clearing)
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
            .heightIn(max = LeziSpacing.DialogContentMax)
            .verticalScroll(rememberScrollState())
            .imePadding()
            .dismissKeyboardOnTap(),
        verticalArrangement = Arrangement.spacedBy(LeziSpacing.Sm),
        content = content,
    )
}

private val visualStylePreviewTypes = listOf(
    RecordType.PEE,
    RecordType.SLEEP,
    RecordType.FORMULA,
)

@Composable
private fun VisualStylePicker(
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(LeziSpacing.Xs),
    ) {
        VisualStylePreviewCard(
            title = "温暖卡片",
            compact = false,
            selected = selected == "warm",
            onClick = { onSelect("warm") },
            modifier = Modifier.weight(1f),
        )
        VisualStylePreviewCard(
            title = "紧凑记录簿",
            compact = true,
            selected = selected == "journal",
            onClick = { onSelect("journal") },
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun VisualStylePreviewCard(
    title: String,
    compact: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val wellShape = if (compact) LeziShapes.JournalCard else CircleShape
    val outline = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)
    }
    Column(
        modifier = modifier
            .heightIn(min = LeziSpacing.Touch)
            .clip(if (compact) LeziShapes.JournalCard else LeziShapes.Sm)
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = outline,
                shape = if (compact) LeziShapes.JournalCard else LeziShapes.Sm,
            )
            .clickable(role = Role.Button, onClickLabel = title, onClick = onClick)
            .padding(LeziSpacing.Sm),
        verticalArrangement = Arrangement.spacedBy(LeziSpacing.Xs),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(LeziSpacing.Xxs)) {
            visualStylePreviewTypes.forEach { type ->
                Box(
                    Modifier
                        .size(LeziIconSize.Chip)
                        .clip(wellShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)),
                    contentAlignment = Alignment.Center,
                ) {
                    RecordTypeIcon(type, size = LeziIconSize.Glyph)
                }
            }
        }
        Text(title, style = LeziTypography.Label)
    }
}

@Composable
internal fun SettingsMenuRow(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    actionLabel: String? = null,
    onClick: (() -> Unit)? = null,
    danger: Boolean = false,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val interaction = if (onClick != null) {
        Modifier.clickable(
            onClickLabel = requireNotNull(actionLabel),
            role = Role.Button,
            onClick = onClick,
        )
    } else {
        Modifier
    }
    LeziSurfacePanel(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = LeziSpacing.Touch)
            .then(interaction),
        bottomBand = true,
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
                        .size(LeziMenuIcon.WellSize)
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
                    if (icon != null) {
                        Icon(
                            icon,
                            contentDescription = null,
                            modifier = Modifier.size(LeziMenuIcon.GlyphSize),
                            tint = if (danger) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        )
                    }
                }
            }
            Spacer(Modifier.size(LeziSpacing.Sm))
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
            } else if (onClick != null) {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag(SETTINGS_MENU_ROW_MORE_TAG),
                )
            }
        }
    }
}
