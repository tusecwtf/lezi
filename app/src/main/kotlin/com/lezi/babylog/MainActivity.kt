package com.lezi.babylog

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.automirrored.outlined.ShowChart
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Person
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.lezi.babylog.core.common.productUiError
import com.lezi.babylog.core.common.LocalDataGate
import com.lezi.babylog.core.common.LocalDataUpgradeState
import com.lezi.babylog.core.datastore.SettingsStore
import com.lezi.babylog.core.model.Baby
import com.lezi.babylog.core.ui.UiTags
import com.lezi.babylog.designsystem.AppBrandBar
import com.lezi.babylog.designsystem.LeziSpacing
import com.lezi.babylog.designsystem.LeziTheme
import com.lezi.babylog.designsystem.LeziTypography
import com.lezi.babylog.domain.CareLog
import com.lezi.babylog.domain.SystemCalendarConfigurationCoordinator
import com.lezi.babylog.domain.babyAgeLabel
import com.lezi.babylog.feature.export.ExportRoute
import com.lezi.babylog.feature.family.FamilyRoute
import com.lezi.babylog.feature.growth.GrowthRoute
import com.lezi.babylog.feature.log.ComposerCreateIntent
import com.lezi.babylog.feature.log.LogRoute
import com.lezi.babylog.feature.log.RecordComposerHost
import com.lezi.babylog.feature.log.TimerHandoffSession
import com.lezi.babylog.feature.log.RecordComposerRequest
import com.lezi.babylog.feature.log.quickDockSnackbarBottomInset
import com.lezi.babylog.feature.onboarding.OnboardingRoute
import com.lezi.babylog.feature.search.SearchRoute
import com.lezi.babylog.feature.settings.CalendarRoute
import com.lezi.babylog.feature.settings.SettingsRoute
import com.lezi.babylog.feature.settings.SystemCalendarSetupDialog
import com.lezi.babylog.feature.summary.SummaryRoute
import com.lezi.babylog.core.model.TimerHandoffSeed
import com.lezi.babylog.feature.timer.TimerRoute
import com.lezi.babylog.feature.widget.CareWidgetRefreshController
import com.lezi.babylog.feature.widget.WidgetComposerContract
import com.lezi.babylog.feature.widget.WidgetComposerTarget
import com.lezi.babylog.sync.AppUpdateCheckResult
import com.lezi.babylog.sync.AppUpdateInstallResult
import com.lezi.babylog.sync.AppUpdateMetadata
import com.lezi.babylog.sync.ClientUpdateRequiredException
import com.lezi.babylog.sync.FamilyRole
import com.lezi.babylog.sync.ForcedAppUpdateState
import com.lezi.babylog.sync.SyncPort
import com.lezi.babylog.sync.forcedUpdateDialogBody
import com.lezi.babylog.sync.forcedUpdatePackageUnknownBody
import com.lezi.babylog.sync.forcedUpdateRetryCheckLabel
import com.lezi.babylog.sync.forcedUpdateTitle
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var localDataGate: LocalDataGate
    private val pendingWidgetComposer = mutableStateOf<WidgetComposerTarget?>(null)
    private val pendingFulfillPlan = mutableStateOf<PendingFulfillPlan?>(null)

    override fun attachBaseContext(newBase: Context) {
        val chineseLocale = Locale.forLanguageTag("zh-CN")
        Locale.setDefault(chineseLocale)
        val localizedConfiguration = Configuration(newBase.resources.configuration).apply {
            setLocale(chineseLocale)
        }
        super.attachBaseContext(newBase.createConfigurationContext(localizedConfiguration))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingWidgetComposer.value = WidgetComposerContract.parse(intent)
        pendingFulfillPlan.value = parseFulfillPlanIntent(intent)
        enableEdgeToEdge()
        setContent {
            val localDataState by localDataGate.state.collectAsStateWithLifecycle()
            val gateScope = rememberCoroutineScope()
            LaunchedEffect(Unit) { localDataGate.ensureReady() }
            if (localDataState is LocalDataUpgradeState.Ready) {
                val vm: RootViewModel = hiltViewModel()
                val ui by vm.ui.collectAsStateWithLifecycle()
                val systemDark = isSystemInDarkTheme()
                val dark = when (ui.darkMode) {
                    "dark" -> true
                    "light" -> false
                    else -> systemDark
                }
                LeziTheme(
                    darkTheme = dark,
                    babyThemeArgb = ui.baby?.themeColorArgb,
                    visualStyle = ui.visualStyle,
                ) {
                    val transparent = Color.Transparent.toArgb()
                    val navigationScrim = MaterialTheme.colorScheme.surface.toArgb()
                    SideEffect {
                        this@MainActivity.enableEdgeToEdge(
                            statusBarStyle = if (dark) {
                                SystemBarStyle.dark(transparent)
                            } else {
                                SystemBarStyle.light(transparent, transparent)
                            },
                            navigationBarStyle = if (dark) {
                                SystemBarStyle.dark(navigationScrim)
                            } else {
                                SystemBarStyle.light(navigationScrim, navigationScrim)
                            },
                        )
                    }
                    LeziRoot(
                        vm = vm,
                        dark = dark,
                        widgetComposerTarget = pendingWidgetComposer.value,
                        onWidgetComposerConsumed = { pendingWidgetComposer.value = null },
                        fulfillPlanTarget = pendingFulfillPlan.value,
                        onFulfillPlanConsumed = { pendingFulfillPlan.value = null },
                    )
                }
            } else {
                LeziTheme {
                    LocalDataUpgradeScreen(
                        state = localDataState,
                        onRetry = { gateScope.launch { localDataGate.retry() } },
                        onShareDiagnostics = {
                            shareLocalDataDiagnostics(
                                this@MainActivity,
                                localDataGate.diagnosticReport(),
                            )
                        },
                        onClearApplicationData = {
                            clearLeziApplicationData(this@MainActivity)
                        },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingWidgetComposer.value = WidgetComposerContract.parse(intent)
        pendingFulfillPlan.value = parseFulfillPlanIntent(intent)
    }

    private fun parseFulfillPlanIntent(intent: Intent?): PendingFulfillPlan? {
        if (intent == null) return null
        // System-calendar L3 deep link: lezi://care-plan/{clientUuid}
        val data = intent.data
        if (data != null &&
            data.scheme == "lezi" &&
            data.host == "care-plan"
        ) {
            val uuid = data.pathSegments?.firstOrNull().orEmpty()
            if (uuid.isNotBlank()) {
                return PendingFulfillPlan(planId = null, clientUuid = uuid)
            }
        }
        val id = intent.getLongExtra(
            com.lezi.babylog.feature.settings.CarePlanReminderReceiver.EXTRA_FULFILL_PLAN_ID,
            0L,
        )
        val uuid = intent.getStringExtra(
            com.lezi.babylog.feature.settings.CarePlanReminderReceiver.EXTRA_FULFILL_PLAN_UUID,
        ).orEmpty()
        if (id <= 0L && uuid.isBlank()) return null
        return PendingFulfillPlan(planId = id.takeIf { it > 0L }, clientUuid = uuid)
    }
}

data class PendingFulfillPlan(
    val planId: Long?,
    val clientUuid: String,
)

data class RootUi(
    val hasBaby: Boolean = false,
    val familyRole: FamilyRole = FamilyRole.None,
    val baby: Baby? = null,
    val babies: List<Baby> = emptyList(),
    val sleeping: Boolean = false,
    val darkMode: String = "system",
    val visualStyle: String = "warm",
    val selectedDate: LocalDate = LocalDate.now(),
    val today: LocalDate = LocalDate.now(),
    val calendarRecordDays: Set<LocalDate> = emptySet(),
    val composerRequest: RecordComposerRequest? = null,
)

@HiltViewModel
class RootViewModel @Inject constructor(
    private val careLog: CareLog,
    private val syncPort: SyncPort,
    private val settings: SettingsStore,
    private val systemCalendarConfiguration: SystemCalendarConfigurationCoordinator,
    private val savedStateHandle: SavedStateHandle,
    private val widgetRefreshController: CareWidgetRefreshController,
) : ViewModel() {
    private val dayFlow = MutableStateFlow(
        clampSelectedDate(
            savedStateHandle.get<Long>(SELECTED_DATE_KEY)?.let(LocalDate::ofEpochDay)
                ?: LocalDate.now(),
        ),
    )
    private val calendarMonthFlow = MutableStateFlow(YearMonth.from(dayFlow.value))
    private val zone = ZoneId.systemDefault()
    private val todayFlow = MutableStateFlow(LocalDate.now(zone))
    private val composerRequestFlow = savedStateHandle.getStateFlow<RecordComposerRequest?>(
        COMPOSER_REQUEST_KEY,
        null,
    )

    init {
        savedStateHandle[SELECTED_DATE_KEY] = dayFlow.value.toEpochDay()
    }

    private val localBaseUi = combine(
        careLog.observeHasBaby(),
        careLog.observeCurrentBaby(),
        careLog.observeBabies(),
        settings.settings,
        dayFlow,
    ) { has, current, babies, s, day ->
        RootUi(
            hasBaby = has,
            baby = current,
            babies = babies,
            darkMode = s.darkMode,
            visualStyle = s.visualStyle,
            selectedDate = day,
        )
    }

    private val baseUi = combine(localBaseUi, syncPort.session()) { base, session ->
        base.copy(familyRole = session.role)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private val sleepingBaby = careLog.observeCurrentBaby().flatMapLatest { baby ->
        if (baby == null) {
            flowOf(null to false)
        } else {
            careLog.observeOpenSleep(baby.id).map { openSleep ->
                baby.id to (openSleep != null)
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private val calendarRecordDays = combine(
        careLog.observeCurrentBaby(),
        calendarMonthFlow,
    ) { baby, month ->
        baby?.id to month
    }.flatMapLatest { (babyId, month) ->
        if (babyId == null) {
            flowOf(emptySet<LocalDate>())
        } else {
            careLog.observeRecords(
                babyId = babyId,
                startDayInclusive = month.atDay(1),
                endDayExclusive = month.plusMonths(1).atDay(1),
                zone = zone,
            ).map { records ->
                records
                    .asSequence()
                    .map { record ->
                        Instant.ofEpochMilli(record.timestamp).atZone(zone).toLocalDate()
                    }
                    .toSet()
            }
        }
    }

    val ui = combine(
        baseUi,
        sleepingBaby,
        calendarRecordDays,
        todayFlow,
        composerRequestFlow,
    ) { base, (sleepingBabyId, sleeping), recordDays, today, composerRequest ->
        base.copy(
            sleeping = sleepingBabyId == base.baby?.id && sleeping,
            calendarRecordDays = recordDays,
            today = today,
            composerRequest = composerRequest,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RootUi())

    /** Device-local system calendar target id (ticket 21); not family-synced. */
    val systemCalendarId = settings.settings
        .map { it.systemCalendarId }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val systemCalendarDisclosureLevel = settings.settings
        .map { it.systemCalendarDisclosureLevel }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 2)

    /** Root-level force-update surface; null when not forced. */
    val forcedAppUpdate: StateFlow<ForcedAppUpdateState?> =
        syncPort.availableForcedAppUpdate()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _forcedUpdateBusy = MutableStateFlow(false)
    val forcedUpdateBusy: StateFlow<Boolean> = _forcedUpdateBusy.asStateFlow()
    private val _forcedUpdateMessage = MutableStateFlow<String?>(null)
    val forcedUpdateMessage: StateFlow<String?> = _forcedUpdateMessage.asStateFlow()
    private val _forcedUpdateNeedsInstallPermission = MutableStateFlow(false)
    val forcedUpdateNeedsInstallPermission: StateFlow<Boolean> =
        _forcedUpdateNeedsInstallPermission.asStateFlow()

    fun installForcedAppUpdate(metadata: AppUpdateMetadata) {
        if (_forcedUpdateBusy.value) return
        viewModelScope.launch {
            _forcedUpdateBusy.value = true
            _forcedUpdateNeedsInstallPermission.value = false
            _forcedUpdateMessage.value = "正在从家庭服务器下载更新包…"
            try {
                val result = syncPort.installAvailableAppUpdate(metadata)
                result.fold(
                    onSuccess = { install ->
                        when (install) {
                            AppUpdateInstallResult.SessionStarted -> {
                                _forcedUpdateNeedsInstallPermission.value = false
                                _forcedUpdateMessage.value =
                                    "请在系统界面确认安装。安装结束后可删除通知；乐记不会在本机留下更新包。"
                            }
                            AppUpdateInstallResult.RequiresInstallPermission -> {
                                _forcedUpdateNeedsInstallPermission.value = true
                                _forcedUpdateMessage.value =
                                    "请允许乐记安装应用，然后再试一次立即更新。"
                            }
                        }
                    },
                    onFailure = { error ->
                        _forcedUpdateNeedsInstallPermission.value = false
                        _forcedUpdateMessage.value =
                            productUiError(error, "下载或安装失败，请稍后重试")
                    },
                )
            } finally {
                _forcedUpdateBusy.value = false
            }
        }
    }

    /**
     * Re-check update metadata while under a force shell (especially [ForcedAppUpdateState.PackageUnknown]).
     * Does not dismiss the force surface; successful Forced metadata upgrades it via [forcedAppUpdate].
     * Never surfaces "当前已是最新版本" while a force shell remains (Result and shell stay force-honest).
     */
    fun retryForcedAppUpdateCheck() {
        if (_forcedUpdateBusy.value) return
        viewModelScope.launch {
            _forcedUpdateBusy.value = true
            _forcedUpdateNeedsInstallPermission.value = false
            _forcedUpdateMessage.value = "正在检查更新…"
            try {
                val result = syncPort.checkAppUpdate()
                // Prefer live port state after classify (StateFlow may lag one frame).
                val shellAfter = syncPort.availableForcedAppUpdate().first()
                result.fold(
                    onSuccess = { check ->
                        _forcedUpdateMessage.value = when {
                            // Installable forced package upgraded or retained — clear busy copy.
                            check is AppUpdateCheckResult.ForcedUpdate -> null
                            shellAfter is ForcedAppUpdateState.WithPackage -> null
                            // PackageUnknown retained / force-honest Result — never "already latest".
                            check is AppUpdateCheckResult.ForcedPackageUnknown ||
                                shellAfter is ForcedAppUpdateState.PackageUnknown ->
                                "仍须更新乐记，但暂时无法从家庭服务器获取更新包，请再试「重试检查更新」。"
                            check is AppUpdateCheckResult.NotJoined ->
                                "请先连接家庭服务器后再检查更新"
                            // Shell cleared (should be rare on retry path).
                            check is AppUpdateCheckResult.UpToDate -> "当前已是最新版本"
                            check is AppUpdateCheckResult.OptionalUpdate -> null
                            else -> null
                        }
                    },
                    onFailure = { error ->
                        _forcedUpdateMessage.value = when {
                            error is ClientUpdateRequiredException ||
                                shellAfter != null ->
                                "仍须更新乐记，但暂时无法从家庭服务器获取更新包，请再试「重试检查更新」。"
                            else -> productUiError(error, "检查更新失败，请稍后重试")
                        }
                    },
                )
            } finally {
                _forcedUpdateBusy.value = false
            }
        }
    }

    fun confirmSystemCalendar(calendarId: String, disclosureLevel: Int) {
        viewModelScope.launch {
            systemCalendarConfiguration.confirm(calendarId, disclosureLevel)
        }
    }

    fun disableSystemCalendar() =
        viewModelScope.launch { systemCalendarConfiguration.disable() }

    fun cycleBaby() {
        viewModelScope.launch {
            val state = ui.value
            val nextId = nextSiblingId(state.babies.map(Baby::id), state.baby?.id)
                ?: return@launch
            careLog.setCurrentBaby(nextId)
        }
    }

    fun jumpSiblingSameDayAge() {
        viewModelScope.launch {
            val state = ui.value
            val current = state.baby ?: return@launch
            val nextId = nextSiblingId(state.babies.map(Baby::id), current.id)
                ?: return@launch
            val next = state.babies.first { it.id == nextId }
            val targetDate = siblingSameDayAgeDate(
                currentBirthdayEpochDay = current.birthdayEpochDay,
                siblingBirthdayEpochDay = next.birthdayEpochDay,
                selectedDate = state.selectedDate,
                today = state.today,
            )
            careLog.setCurrentBaby(next.id)
            updateSelectedDate(targetDate)
        }
    }

    fun toggleDark() {
        viewModelScope.launch {
            val next = when (ui.value.darkMode) {
                "dark" -> "light"
                else -> "dark"
            }
            settings.setDarkMode(next)
        }
    }

    fun shiftDay(delta: Long) {
        updateSelectedDate(dayFlow.value.plusDays(delta))
    }

    fun setDay(day: LocalDate) {
        updateSelectedDate(day)
    }

    fun setCalendarMonth(month: YearMonth) {
        val currentMonth = YearMonth.from(todayFlow.value)
        calendarMonthFlow.value = if (month > currentMonth) currentMonth else month
    }

    fun refreshToday() {
        val current = LocalDate.now(zone)
        val previous = todayFlow.value
        if (current == previous) return
        todayFlow.value = current
        if (dayFlow.value == previous) {
            updateSelectedDate(current)
        }
    }

    fun openWidgetBaby(babyId: Long) {
        viewModelScope.launch { careLog.setCurrentBaby(babyId) }
    }

    fun openComposer(request: RecordComposerRequest) {
        savedStateHandle[COMPOSER_REQUEST_KEY] = request
    }

    fun closeComposer() {
        savedStateHandle[COMPOSER_REQUEST_KEY] = null
    }

    /**
     * Consume the restorable root request after a durable Composer fact/plan write.
     * Idempotent: widgets refresh only when a root request was actually open, so Host
     * re-subscribe / rotation while a post-save stage remains cannot spam refresh.
     */
    fun closeComposerAfterPersist() {
        val hadRequest = savedStateHandle.get<RecordComposerRequest>(COMPOSER_REQUEST_KEY) != null
        savedStateHandle[COMPOSER_REQUEST_KEY] = null
        if (hadRequest) {
            refreshWidgets()
        }
    }

    /** Resolve a stable plan UUID from a notification deep link; null if missing/terminal. */
    suspend fun resolveCarePlanId(clientUuid: String): Long? {
        val plan = careLog.getCarePlanByClientUuid(clientUuid) ?: return null
        if (plan.deletedAt != null) return null
        return plan.id
    }

    fun refreshWidgets() {
        viewModelScope.launch { widgetRefreshController.refreshAll() }
    }

    private fun updateSelectedDate(day: LocalDate) {
        val selected = clampSelectedDate(day, todayFlow.value)
        dayFlow.value = selected
        savedStateHandle[SELECTED_DATE_KEY] = selected.toEpochDay()
    }

    private companion object {
        const val SELECTED_DATE_KEY = "root_selected_date_epoch_day"
        const val COMPOSER_REQUEST_KEY = "root_record_composer_request"
    }
}

internal fun clampSelectedDate(
    requested: LocalDate,
    today: LocalDate = LocalDate.now(),
): LocalDate = if (requested.isAfter(today)) today else requested

internal fun siblingSameDayAgeDate(
    currentBirthdayEpochDay: Long,
    siblingBirthdayEpochDay: Long,
    selectedDate: LocalDate,
    today: LocalDate = LocalDate.now(),
): LocalDate {
    val currentBirth = LocalDate.ofEpochDay(currentBirthdayEpochDay)
    val siblingBirth = LocalDate.ofEpochDay(siblingBirthdayEpochDay)
    val ageInWholeDays = ChronoUnit.DAYS.between(currentBirth, selectedDate)
    return clampSelectedDate(siblingBirth.plusDays(ageInWholeDays), today)
}

internal fun nextSiblingId(
    babyIds: List<Long>,
    currentId: Long?,
): Long? {
    if (babyIds.size < 2) return null
    val currentIndex = babyIds.indexOf(currentId)
    val next = if (currentIndex >= 0) {
        babyIds[(currentIndex + 1) % babyIds.size]
    } else {
        babyIds.first()
    }
    return next.takeIf { it != currentId }
}

private enum class TopDest(
    val route: String,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
) {
    Log("log", "记录", Icons.Filled.GridView, Icons.Outlined.GridView),
    Summary("summary", "汇总", Icons.Filled.BarChart, Icons.Outlined.BarChart),
    Growth(
        "growth",
        "成长",
        Icons.AutoMirrored.Filled.ShowChart,
        Icons.AutoMirrored.Outlined.ShowChart,
    ),
    Family("family", "账户", Icons.Filled.Person, Icons.Outlined.Person),
    Settings("settings", "菜单", Icons.Filled.MoreHoriz, Icons.Outlined.MoreHoriz),
}

internal data class RootChromeVisibility(
    val showTopBar: Boolean,
    val showBottomBar: Boolean,
    val preserveBottomBarExtent: Boolean,
)

internal fun rootChromeVisibility(
    route: String?,
    logLayoutEditActive: Boolean,
): RootChromeVisibility {
    val routeOwnsFullScreen = route?.startsWith("timer") == true ||
        route == "search" ||
        route == "export" ||
        route == "calendar"
    val editorOwnsFullScreen = route == TopDest.Log.route && logLayoutEditActive
    val hideChrome = routeOwnsFullScreen || editorOwnsFullScreen
    val hasTopBar = route in setOf(
        TopDest.Log.route,
        TopDest.Summary.route,
        TopDest.Growth.route,
    ) || route == TopDest.Family.route || route?.startsWith(TopDest.Settings.route) == true
    return RootChromeVisibility(
        showTopBar = !hideChrome && hasTopBar,
        showBottomBar = !hideChrome,
        preserveBottomBarExtent = editorOwnsFullScreen,
    )
}

internal fun rootSnackbarBottomInset(
    route: String?,
    logLayoutEditActive: Boolean,
) = if (route == TopDest.Log.route && !logLayoutEditActive) {
    quickDockSnackbarBottomInset
} else {
    0.dp
}

@Composable
fun LeziRoot(
    vm: RootViewModel = hiltViewModel(),
    dark: Boolean = false,
    widgetComposerTarget: WidgetComposerTarget? = null,
    fulfillPlanTarget: PendingFulfillPlan? = null,
    onFulfillPlanConsumed: () -> Unit = {},
    onWidgetComposerConsumed: () -> Unit = {},
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    // Force shell is above onboarding so a joined device still under baby setup cannot
    // silently miss PackageUnknown/WithPackage after client_update_required.
    Box(Modifier.fillMaxSize()) {
        if (shouldShowOnboarding(ui.hasBaby, ui.familyRole)) {
            // Baby creation updates this route through CareLog; no completion callback is needed.
            OnboardingRoute(onFinished = {})
        } else {
            LeziMainScaffold(
                vm = vm,
                ui = ui,
                dark = dark,
                widgetComposerTarget = widgetComposerTarget,
                fulfillPlanTarget = fulfillPlanTarget,
                onFulfillPlanConsumed = onFulfillPlanConsumed,
                onWidgetComposerConsumed = onWidgetComposerConsumed,
            )
        }
        RootForcedAppUpdateLayer(vm)
    }
}

@Composable
private fun RootForcedAppUpdateLayer(vm: RootViewModel) {
    val forcedUpdate by vm.forcedAppUpdate.collectAsStateWithLifecycle()
    val forcedBusy by vm.forcedUpdateBusy.collectAsStateWithLifecycle()
    val forcedMessage by vm.forcedUpdateMessage.collectAsStateWithLifecycle()
    val needsInstallPermission by vm.forcedUpdateNeedsInstallPermission.collectAsStateWithLifecycle()
    forcedUpdate?.let { forced ->
        ForcedAppUpdateOverlay(
            forced = forced,
            busy = forcedBusy,
            message = forcedMessage,
            needsInstallPermission = needsInstallPermission,
            onInstall = { metadata -> vm.installForcedAppUpdate(metadata) },
            onRetryCheck = vm::retryForcedAppUpdateCheck,
        )
    }
}

@Composable
private fun LeziMainScaffold(
    vm: RootViewModel,
    ui: RootUi,
    dark: Boolean,
    widgetComposerTarget: WidgetComposerTarget?,
    fulfillPlanTarget: PendingFulfillPlan?,
    onFulfillPlanConsumed: () -> Unit,
    onWidgetComposerConsumed: () -> Unit,
) {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val current = backStack?.destination?.route
    val today = ui.today
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val composerRequest = ui.composerRequest
    val systemCalendarId by vm.systemCalendarId.collectAsStateWithLifecycle()
    val systemCalendarDisclosureLevel by vm.systemCalendarDisclosureLevel.collectAsStateWithLifecycle()
    var showHeaderCalendar by remember { mutableStateOf(false) }
    var showSystemCalendarSetup by remember { mutableStateOf(false) }
    var displayedMonth by remember { mutableStateOf(YearMonth.from(ui.selectedDate)) }
    var logLayoutEditActive by remember { mutableStateOf(false) }
    /**
     * Composer→Timer handoff session captured at navigate time (Ticket 09).
     * Process-local only; durable accept/reject settle via saveable tokens below so
     * process death cannot drop Composer release while Timer already holds the seed.
     */
    var timerHandoffSession by remember {
        mutableStateOf<TimerHandoffSession?>(null)
    }
    /** Durable accept token re-delivered to Composer Host after process death. */
    var acceptedTimerHandoffSeedJson by rememberSaveable {
        mutableStateOf<String?>(null)
    }
    /** Monotonic reject / leave-before-accept signal for Host cancelTimerHandoff. */
    var timerHandoffRejectEpoch by rememberSaveable { mutableIntStateOf(0) }

    LaunchedEffect(widgetComposerTarget) {
        val target = widgetComposerTarget ?: return@LaunchedEffect
        // Never replace an in-progress draft without an explicit discard action.
        if (composerRequest != null) {
            onWidgetComposerConsumed()
            return@LaunchedEffect
        }
        if (ui.babies.none { it.id == target.babyId }) {
            onWidgetComposerConsumed()
            return@LaunchedEffect
        }
        vm.openWidgetBaby(target.babyId)
        vm.openComposer(
            RecordComposerRequest.New(
                babyId = target.babyId,
                type = target.type,
                timestamp = System.currentTimeMillis(),
                historical = false,
            ),
        )
        onWidgetComposerConsumed()
    }

    LaunchedEffect(fulfillPlanTarget) {
        val target = fulfillPlanTarget ?: return@LaunchedEffect
        if (composerRequest != null) {
            onFulfillPlanConsumed()
            return@LaunchedEffect
        }
        val planId = target.planId
            ?: target.clientUuid.takeIf { it.isNotBlank() }?.let { uuid ->
                vm.resolveCarePlanId(uuid)
            }
        if (planId != null && planId > 0L) {
            vm.openComposer(RecordComposerRequest.Fulfill(planId))
        }
        onFulfillPlanConsumed()
    }
    val chrome = rootChromeVisibility(current, logLayoutEditActive)
    val showContextHeader = current in setOf(
        TopDest.Log.route,
        TopDest.Summary.route,
        TopDest.Growth.route,
    )
    val showBrandHeader = current == TopDest.Family.route ||
        current?.startsWith(TopDest.Settings.route) == true
    val snackbarBottomInset = rootSnackbarBottomInset(current, logLayoutEditActive)

    LaunchedEffect(today) {
        vm.refreshToday()
        val now = ZonedDateTime.now(ZoneId.systemDefault())
        val nextMidnight = now.toLocalDate().plusDays(1).atStartOfDay(now.zone)
        val waitMillis = Duration.between(now, nextMidnight)
            .toMillis()
            .coerceAtLeast(1_000L)
        delay(waitMillis)
        vm.refreshToday()
    }

    Scaffold(
        modifier = Modifier.testTag(UiTags.ROOT),
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = {
            SnackbarHost(
                hostState = snackbar,
                modifier = Modifier
                    .padding(bottom = snackbarBottomInset)
                    .testTag("root_snackbar_host"),
            )
        },
        topBar = {
            when {
                chrome.showTopBar && showContextHeader -> {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .background(
                                if (dark) {
                                    MaterialTheme.colorScheme.surface
                                } else {
                                    com.lezi.babylog.designsystem.LeziThemeExt.colors.babyAccent
                                },
                            )
                            .statusBarsPadding(),
                    ) {
                        AppHeaderBar(
                            babyName = ui.baby?.nickname.orEmpty(),
                            babyAge = ui.baby?.let { babyAgeLabel(it.birthdayEpochDay) }.orEmpty(),
                            avatarPath = ui.baby?.avatarPath,
                            sleeping = ui.sleeping,
                            selectedDate = ui.selectedDate,
                            today = today,
                            canCycleBaby = ui.babies.size > 1,
                            canGoNext = ui.selectedDate.isBefore(today),
                            dark = dark,
                            onCycleBaby = { vm.cycleBaby() },
                            onJumpSiblingSameDayAge = { vm.jumpSiblingSameDayAge() },
                            onPreviousDate = { vm.shiftDay(-1) },
                            onNextDate = { vm.shiftDay(1) },
                            onOpenDatePicker = {
                                displayedMonth = YearMonth.from(ui.selectedDate)
                                vm.setCalendarMonth(displayedMonth)
                                showHeaderCalendar = true
                            },
                            onSearch = { nav.navigate("search") },
                        )
                    }
                }
                chrome.showTopBar && showBrandHeader -> {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .background(
                                if (dark) {
                                    MaterialTheme.colorScheme.surface
                                } else {
                                    com.lezi.babylog.designsystem.LeziThemeExt.colors.babyAccent
                                },
                            )
                            .statusBarsPadding(),
                    ) {
                        AppBrandBar(
                            onToggleTheme = { vm.toggleDark() },
                            dark = dark,
                        )
                    }
                }
            }
        },
        bottomBar = {
            if (chrome.showBottomBar) {
                val sky = com.lezi.babylog.designsystem.LeziThemeExt.colors.skySoft
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
                    tonalElevation = 0.dp,
                ) {
                    TopDest.entries.forEach { dest ->
                        val selected = current == dest.route ||
                            (dest == TopDest.Log && current?.startsWith("log") == true) ||
                            (dest == TopDest.Settings && current?.startsWith("settings") == true)
                        val navigateToDestination = {
                            nav.navigate(dest.route) {
                                popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                        NavigationBarItem(
                            selected = selected,
                            onClick = navigateToDestination,
                            modifier = Modifier.pointerInput(dest) {
                                detectTapGestures(
                                    onLongPress = {
                                        if (dest == TopDest.Log ||
                                            dest == TopDest.Summary ||
                                            dest == TopDest.Growth
                                        ) {
                                            vm.cycleBaby()
                                        }
                                    },
                                    onTap = { navigateToDestination() },
                                )
                            },
                            icon = {
                                Icon(
                                    if (selected) dest.selectedIcon else dest.unselectedIcon,
                                    contentDescription = dest.label,
                                )
                            },
                            label = { Text(dest.label) },
                            colors = NavigationBarItemDefaults.colors(
                                indicatorColor = sky,
                                selectedIconColor = MaterialTheme.colorScheme.onBackground,
                                selectedTextColor = MaterialTheme.colorScheme.onBackground,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                        )
                    }
                }
            } else if (chrome.preserveBottomBarExtent) {
                // Keep the editor Dock aligned with its everyday position without
                // exposing a second copy of the primary navigation tabs.
                NavigationBar(
                    modifier = Modifier.testTag("layout_edit_primary_nav_reserved"),
                    containerColor = MaterialTheme.colorScheme.background,
                    tonalElevation = 0.dp,
                ) {}
            }
        },
    ) { padding ->
        NavHost(
            navController = nav,
            startDestination = if (!ui.hasBaby && ui.familyRole == FamilyRole.Member) {
                TopDest.Family.route
            } else {
                TopDest.Log.route
            },
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding),
            enterTransition = { EnterTransition.None },
            exitTransition = { ExitTransition.None },
            popEnterTransition = { EnterTransition.None },
            popExitTransition = { ExitTransition.None },
        ) {
            composable(TopDest.Log.route) {
                LogRoute(
                    externalDay = ui.selectedDate,
                    onOpenComposer = vm::openComposer,
                    onGoToday = { vm.setDay(today) },
                    onLayoutEditModeChanged = { active ->
                        logLayoutEditActive = active
                    },
                    onMessage = { message ->
                        scope.launch { snackbar.showSnackbar(message) }
                    },
                )
            }
            composable(TopDest.Summary.route) { SummaryRoute(anchorDate = ui.selectedDate) }
            composable(TopDest.Growth.route) { GrowthRoute(initialDate = ui.selectedDate) }
            composable(TopDest.Family.route) {
                FamilyRoute(onAddBaby = { nav.navigate("settings/add-baby") })
            }
            composable(TopDest.Settings.route) {
                SettingsRoute(
                    onOpenExport = { nav.navigate("export") },
                    onOpenSearch = { nav.navigate("search") },
                    onOpenCalendar = { nav.navigate("calendar") },
                )
            }
            composable("settings/add-baby") {
                SettingsRoute(
                    onOpenExport = { nav.navigate("export") },
                    onOpenSearch = { nav.navigate("search") },
                    onOpenCalendar = { nav.navigate("calendar") },
                    initiallyShowAddBaby = true,
                    onInitialAddBabyFinished = { nav.popBackStack() },
                )
            }
            composable("search") {
                SearchRoute(
                    onBack = { nav.popBackStack() },
                    onOpenEdit = { id ->
                        vm.openComposer(RecordComposerRequest.Edit(id))
                    },
                )
            }
            composable("export") {
                ExportRoute(onBack = { nav.popBackStack() })
            }
            composable("calendar") {
                CalendarRoute(
                    onBack = { nav.popBackStack() },
                    initialDate = ui.selectedDate,
                    onScheduleCare = { type, scheduledAt, customItemId ->
                        val babyId = ui.baby?.id ?: return@CalendarRoute
                        vm.openComposer(
                            RecordComposerRequest.New(
                                babyId = babyId,
                                type = type,
                                timestamp = scheduledAt,
                                historical = false,
                                customItemId = customItemId,
                                createIntent = ComposerCreateIntent.ScheduleCare,
                            ),
                        )
                    },
                    onFulfillPlan = { planId ->
                        vm.openComposer(RecordComposerRequest.Fulfill(planId))
                    },
                    onEditPlan = { planId ->
                        vm.openComposer(RecordComposerRequest.EditPlan(planId))
                    },
                )
            }
            composable("timer") {
                val source = nav.previousBackStackEntry?.savedStateHandle
                val handoffSeed = TimerHandoffSeed.fromJson(
                    source?.get<String>(TIMER_HANDOFF_SEED_JSON_KEY),
                )
                TimerRoute(
                    initialNote = handoffSeed?.note.orEmpty(),
                    initialAmountMl = handoffSeed?.amountMl.orEmpty(),
                    carePlanId = handoffSeed?.carePlanId,
                    babyId = handoffSeed?.babyId,
                    handoffSeed = handoffSeed,
                    onHandoffAccepted = { seed ->
                        // Timer owns the seed. Durable token drives Host release even when
                        // process-local session is null after death (AlreadyAccepted path).
                        timerHandoffSession = null
                        acceptedTimerHandoffSeedJson = seed.toJson()
                        source?.remove<String>(TIMER_HANDOFF_SEED_JSON_KEY)
                    },
                    onHandoffRejected = {
                        // Keep Composer draft + photos editable; leave timer route.
                        timerHandoffSession = null
                        timerHandoffRejectEpoch += 1
                        source?.remove<String>(TIMER_HANDOFF_SEED_JSON_KEY)
                        scope.launch {
                            snackbar.showSnackbar("当前已有进行中的计时，草稿仍可编辑")
                        }
                        nav.popBackStack()
                    },
                    onDone = {
                        // Pop / discard before accept: unlock Composer without releasing files.
                        timerHandoffSession = null
                        timerHandoffRejectEpoch += 1
                        source?.remove<String>(TIMER_HANDOFF_SEED_JSON_KEY)
                        nav.popBackStack()
                    },
                )
            }
        }
    }

    // Drop orphan accept token if Composer root is already gone (e.g. double-deliver).
    LaunchedEffect(composerRequest, acceptedTimerHandoffSeedJson) {
        if (composerRequest == null && acceptedTimerHandoffSeedJson != null) {
            acceptedTimerHandoffSeedJson = null
        }
    }

    RecordComposerHost(
        request = composerRequest,
        onDismiss = vm::closeComposer,
        onPersisted = vm::closeComposerAfterPersist,
        onSaved = { message ->
            scope.launch { snackbar.showSnackbar(message) }
        },
        onStartNursingTimer = { session ->
            // Ownership transfer: do NOT close Composer until Timer accepts seed.
            // Sole navigate payload is seed JSON; accept/reject settle via saveable tokens.
            timerHandoffSession = session
            nav.currentBackStackEntry?.savedStateHandle?.set(
                TIMER_HANDOFF_SEED_JSON_KEY,
                session.seed.toJson(),
            )
            nav.navigate("timer")
        },
        acceptedTimerHandoffSeedJson = acceptedTimerHandoffSeedJson,
        onAcceptedTimerHandoffConsumed = { acceptedTimerHandoffSeedJson = null },
        timerHandoffRejectEpoch = timerHandoffRejectEpoch,
        // Ticket 21: unconfigured plan switch → explicit setup; dismiss still allows save.
        onConfigureSystemCalendar = { showSystemCalendarSetup = true },
    )

    if (showSystemCalendarSetup) {
        SystemCalendarSetupDialog(
            currentCalendarId = systemCalendarId,
            currentDisclosureLevel = systemCalendarDisclosureLevel,
            onConfirm = { selection ->
                vm.confirmSystemCalendar(
                    selection.calendarId,
                    selection.disclosureLevel,
                )
                showSystemCalendarSetup = false
            },
            onDisable = {
                vm.disableSystemCalendar()
                showSystemCalendarSetup = false
            },
            onDismiss = { showSystemCalendarSetup = false },
        )
    }

    if (showHeaderCalendar) {
        HeaderCalendarDialog(
            selectedDate = ui.selectedDate,
            displayedMonth = displayedMonth,
            today = today,
            recordDays = ui.calendarRecordDays,
            onMonthChange = { requested ->
                displayedMonth = if (requested > YearMonth.from(today)) {
                    YearMonth.from(today)
                } else {
                    requested
                }
                vm.setCalendarMonth(displayedMonth)
            },
            onSelect = { selected ->
                vm.setDay(selected)
                showHeaderCalendar = false
            },
            onDismiss = { showHeaderCalendar = false },
        )
    }
}

/**
 * Full-screen, non-dismissible force-update gate (no "稍后").
 * Covers the whole activity content so log/summary/account cannot be used to bypass.
 * System back is consumed and the surface sinks pointer events so taps cannot reach
 * the scaffold or onboarding underneath.
 */
@Composable
private fun ForcedAppUpdateOverlay(
    forced: ForcedAppUpdateState,
    busy: Boolean,
    message: String?,
    needsInstallPermission: Boolean,
    onInstall: (AppUpdateMetadata) -> Unit,
    onRetryCheck: () -> Unit,
) {
    val context = LocalContext.current
    // Consume system back while forced — no "稍后" and no back-to-main bypass.
    BackHandler(enabled = true) { }
    val body = when (forced) {
        is ForcedAppUpdateState.WithPackage -> forcedUpdateDialogBody(forced.metadata)
        ForcedAppUpdateState.PackageUnknown -> forcedUpdatePackageUnknownBody()
    }
    val sinkInteraction = remember { MutableInteractionSource() }
    Surface(
        modifier = Modifier
            .fillMaxSize()
            // Absorb taps so main tabs / onboarding under the mask cannot be used.
            .clickable(
                interactionSource = sinkInteraction,
                indication = null,
                onClick = {},
            )
            .semantics { contentDescription = "强制更新乐记" }
            .testTag("forced_app_update_overlay"),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(LeziSpacing.Lg),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                forcedUpdateTitle(),
                style = LeziTypography.TitleSm,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.height(LeziSpacing.Md))
            Text(
                body,
                style = LeziTypography.Body,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!message.isNullOrBlank()) {
                Spacer(Modifier.height(LeziSpacing.Md))
                Text(
                    message,
                    style = LeziTypography.Meta,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(LeziSpacing.Xl))
            when (forced) {
                is ForcedAppUpdateState.WithPackage -> {
                    Button(
                        onClick = { onInstall(forced.metadata) },
                        enabled = !busy,
                    ) {
                        Text(if (busy) "安装中…" else "立即更新")
                    }
                    Spacer(Modifier.height(LeziSpacing.Sm))
                    TextButton(
                        onClick = onRetryCheck,
                        enabled = !busy,
                    ) {
                        Text(forcedUpdateRetryCheckLabel())
                    }
                }
                ForcedAppUpdateState.PackageUnknown -> {
                    Button(
                        onClick = onRetryCheck,
                        enabled = !busy,
                    ) {
                        Text(if (busy) "检查中…" else forcedUpdateRetryCheckLabel())
                    }
                }
            }
            if (needsInstallPermission) {
                Spacer(Modifier.height(LeziSpacing.Sm))
                TextButton(
                    onClick = {
                        val intent = Intent(
                            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:${context.packageName}"),
                        )
                        runCatching { context.startActivity(intent) }
                    },
                ) {
                    Text("去设置")
                }
            }
        }
    }
}

internal fun shouldShowOnboarding(hasBaby: Boolean, familyRole: FamilyRole): Boolean =
    !hasBaby && familyRole != FamilyRole.Member

/** Sole Composer→Timer navigate payload (Ticket 09). */
private const val TIMER_HANDOFF_SEED_JSON_KEY = "timer_handoff_seed_json"
