package com.lezi.babylog

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
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
import com.lezi.babylog.core.datastore.SettingsStore
import com.lezi.babylog.core.model.Baby
import com.lezi.babylog.core.ui.UiTags
import com.lezi.babylog.designsystem.AppBrandBar
import com.lezi.babylog.designsystem.LeziTheme
import com.lezi.babylog.domain.CareLog
import com.lezi.babylog.domain.SystemCalendarConfigurationCoordinator
import com.lezi.babylog.domain.babyAgeLabel
import com.lezi.babylog.feature.export.ExportRoute
import com.lezi.babylog.feature.family.FamilyRoute
import com.lezi.babylog.feature.growth.GrowthRoute
import com.lezi.babylog.feature.log.ComposerCreateIntent
import com.lezi.babylog.feature.log.LogRoute
import com.lezi.babylog.feature.log.RecordComposerHost
import com.lezi.babylog.feature.log.RecordComposerRequest
import com.lezi.babylog.feature.onboarding.OnboardingRoute
import com.lezi.babylog.feature.search.SearchRoute
import com.lezi.babylog.feature.settings.CalendarRoute
import com.lezi.babylog.feature.settings.SettingsRoute
import com.lezi.babylog.feature.settings.SystemCalendarSetupDialog
import com.lezi.babylog.feature.summary.SummaryRoute
import com.lezi.babylog.feature.timer.TimerRoute
import com.lezi.babylog.feature.widget.CareWidgetRefreshController
import com.lezi.babylog.feature.widget.WidgetComposerContract
import com.lezi.babylog.feature.widget.WidgetComposerTarget
import com.lezi.babylog.sync.FamilyRole
import com.lezi.babylog.sync.SyncPort
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
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
            if (state.babies.size < 2) return@launch
            val cur = state.baby?.id
            val idx = state.babies.indexOfFirst { it.id == cur }.takeIf { it >= 0 } ?: 0
            val next = state.babies[(idx + 1) % state.babies.size]
            careLog.setCurrentBaby(next.id)
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
    if (shouldShowOnboarding(ui.hasBaby, ui.familyRole)) {
        // Baby creation updates this route through CareLog; no completion callback is needed.
        OnboardingRoute(onFinished = {})
        return
    }
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
    val hideChrome = current?.startsWith("timer") == true ||
        current == "search" ||
        current == "export" ||
        current == "calendar"
    val showContextHeader = current in setOf(
        TopDest.Log.route,
        TopDest.Summary.route,
        TopDest.Growth.route,
    )
    val showBrandHeader = current == TopDest.Family.route ||
        current?.startsWith(TopDest.Settings.route) == true

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
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            when {
                !hideChrome && showContextHeader -> {
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
                !hideChrome && showBrandHeader -> {
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
            if (!hideChrome) {
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
                    onOpenQuickSlotSettings = { nav.navigate("settings/quick-records") },
                    onGoToday = { vm.setDay(today) },
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
            composable("settings/quick-records") {
                SettingsRoute(
                    onOpenExport = { nav.navigate("export") },
                    onOpenSearch = { nav.navigate("search") },
                    onOpenCalendar = { nav.navigate("calendar") },
                    initiallyShowQuickSlots = true,
                    onInitialQuickSlotsFinished = { nav.popBackStack() },
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
                TimerRoute(
                    initialNote = source?.get<String>(TIMER_SEED_NOTE_KEY).orEmpty(),
                    initialAmountMl = source?.get<String>(TIMER_SEED_AMOUNT_KEY).orEmpty(),
                    carePlanId = source?.get<Long>(TIMER_SEED_CARE_PLAN_ID_KEY),
                    babyId = source?.get<Long>(TIMER_SEED_BABY_ID_KEY),
                    onDone = { nav.popBackStack() },
                )
            }
        }
    }

    RecordComposerHost(
        request = composerRequest,
        onDismiss = vm::closeComposer,
        onPersisted = {
            vm.closeComposer()
            vm.refreshWidgets()
        },
        onSaved = { message ->
            scope.launch { snackbar.showSnackbar(message) }
        },
        onStartNursingTimer = { note, amountMl, carePlanId, babyId ->
            vm.closeComposer()
            nav.currentBackStackEntry?.savedStateHandle?.apply {
                set(TIMER_SEED_NOTE_KEY, note)
                set(TIMER_SEED_AMOUNT_KEY, amountMl)
                set(TIMER_SEED_CARE_PLAN_ID_KEY, carePlanId)
                set(TIMER_SEED_BABY_ID_KEY, babyId)
            }
            nav.navigate("timer")
        },
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

internal fun shouldShowOnboarding(hasBaby: Boolean, familyRole: FamilyRole): Boolean =
    !hasBaby && familyRole != FamilyRole.Member

private const val TIMER_SEED_NOTE_KEY = "timer_seed_note"
private const val TIMER_SEED_AMOUNT_KEY = "timer_seed_amount_ml"
private const val TIMER_SEED_CARE_PLAN_ID_KEY = "timer_seed_care_plan_id"
private const val TIMER_SEED_BABY_ID_KEY = "timer_seed_baby_id"
